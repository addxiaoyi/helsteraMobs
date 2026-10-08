package dev.helstera.core.animation;

import java.util.List;
import java.util.Map;

/**
 * 一段动画剪辑。
 *
 * <p><b>为什么加防御性复制</b>：见 {@link KeyframeTrack} 的同名说明——
 * record 的自动访问器直接暴露构造时传入的集合，动画数据一旦被外部改一次，
 * 采样结果就永久错乱且极难定位。构造时复制一次即可根除。
 * 代价只在模型加载时发生（每模型数个剪辑），可忽略。</p>
 */
public record AnimationClip(String name, boolean loop, double length,
                            Map<String, KeyframeTrack.BoneTracks> bones,
                            List<EventMarker> events) {

    public AnimationClip {
        bones = bones == null ? Map.of() : Map.copyOf(bones);
        events = events == null ? List.of() : List.copyOf(events);
    }

    /** 动画事件标记：attack_hit / sound / particle / skill 等。 */
    public record EventMarker(double time, String marker, String data) {
    }

    /** 归一化时间（考虑循环）。 */
    public double normalize(double t) {
        if (length <= 0) return 0;
        if (loop) {
            double m = t % length;
            return m < 0 ? m + length : m;
        }
        return Math.min(t, length);
    }
}
