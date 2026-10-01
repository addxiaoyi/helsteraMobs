package dev.helstera.core.animation;

import java.util.List;
import java.util.Map;

/**
 * 一段动画剪辑。
 */
public record AnimationClip(String name, boolean loop, double length,
                            Map<String, KeyframeTrack.BoneTracks> bones,
                            List<EventMarker> events) {

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
