package dev.helstera.core.animation;

import dev.helstera.api.Vec3;

import java.util.List;
import java.util.Map;

/**
 * 一条关键帧轨道（某骨骼的 rotation / position / scale）。
 * 帧按 time 升序；value 含义：rotation=度，position=模型像素，scale=倍率。
 *
 * <p><b>为什么加防御性复制</b>：record 的自动访问器直接返回构造时传入的
 * 集合引用。动画系统里 frames 一旦被外部 {@code add()} 改一次，
 * 整条轨道的采样结果就永久错乱，且极难定位——因为写的地方和出问题的地方
 * 隔着整个渲染循环。构造时 {@code copyOf} 一次即可根除这类隐患。
 * 代价是构造时多一次拷贝，而这些轨道只在模型加载时构造一次。</p>
 */
public record KeyframeTrack(String channel, List<Keyframe> frames) {

    /** 关键帧列表在构造时复制，外部无法再改动本轨道。 */
    public KeyframeTrack {
        frames = frames == null ? List.of() : List.copyOf(frames);
    }

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

    /**
     * 该骨骼所有通道（rotation/position/scale）。
     *
     * <p>同样做防御性复制，理由见 {@link KeyframeTrack}。</p>
     */
    public record BoneTracks(Map<String, KeyframeTrack> channels) {
        public BoneTracks {
            channels = channels == null ? Map.of() : Map.copyOf(channels);
        }
    }
}
