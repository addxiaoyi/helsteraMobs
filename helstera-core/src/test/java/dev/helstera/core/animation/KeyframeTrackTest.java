package dev.helstera.core.animation;

import dev.helstera.api.Vec3;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 关键帧采样与时间归一化的契约测试。
 *
 * <p>采样是动画表现的核心：插值写错不会报错，只会表现为「动作很怪」，
 * 因此这里把端点夹取、step、smoothstep、反向采样逐个钉死。</p>
 */
class KeyframeTrackTest {

    private static KeyframeTrack track(String interp, KeyframeTrack.Keyframe... frames) {
        return new KeyframeTrack("rotation", List.of(frames));
    }

    @Test
    @DisplayName("单帧轨道恒定返回该帧值")
    void singleFrameIsConstant() {
        var t = new KeyframeTrack("position", List.of(new KeyframeTrack.Keyframe(0, new Vec3(1, 2, 3), "linear")));
        assertEquals(new Vec3(1, 2, 3), t.sample(0, false));
        assertEquals(new Vec3(1, 2, 3), t.sample(5, false));
    }

    @Test
    @DisplayName("空轨道返回零向量，不抛异常")
    void emptyTrackIsZero() {
        assertEquals(Vec3.ZERO, new KeyframeTrack("scale", List.of()).sample(1, false));
    }

    @Test
    @DisplayName("时间超出轨道范围时夹取到首/尾帧")
    void clampsOutsideRange() {
        var t = track("linear",
                new KeyframeTrack.Keyframe(1, new Vec3(0, 0, 0), "linear"),
                new KeyframeTrack.Keyframe(2, new Vec3(10, 0, 0), "linear"));

        assertEquals(new Vec3(0, 0, 0), t.sample(0, false), "早于首帧取首帧");
        assertEquals(new Vec3(0, 0, 0), t.sample(0.5, false));
        assertEquals(new Vec3(10, 0, 0), t.sample(99, false), "晚于尾帧取尾帧");
    }

    @Test
    @DisplayName("step 插值在区间内保持起始帧值，不做过渡")
    void stepHoldsStartValue() {
        var t = track("linear",
                new KeyframeTrack.Keyframe(0, new Vec3(0, 0, 0), "step"),
                new KeyframeTrack.Keyframe(2, new Vec3(10, 0, 0), "linear"));

        assertEquals(new Vec3(0, 0, 0), t.sample(1.99, false));
        assertEquals(new Vec3(10, 0, 0), t.sample(2.0, false), "到达下一帧才跳变");
    }

    @Test
    @DisplayName("线性轨道在中点取 smoothstep 后的插值（不是简单平均）")
    void linearUsesSmoothstep() {
        var t = track("linear",
                new KeyframeTrack.Keyframe(0, new Vec3(0, 0, 0), "linear"),
                new KeyframeTrack.Keyframe(2, new Vec3(10, 0, 0), "linear"));

        // alpha=0.5 -> smoothstep(0.5)=0.5
        assertEquals(5.0, t.sample(1, false).x(), 1e-9);
        // alpha=0.25 -> smoothstep = 0.25^2*(3-2*0.25)=0.15625
        assertEquals(1.5625, t.sample(0.5, false).x(), 1e-9);
        assertEquals(8.4375, t.sample(1.5, false).x(), 1e-9);
    }

    @Test
    @DisplayName("反向采样按轨道时长镜像时间")
    void reverseMirrorsTime() {
        var t = track("linear",
                new KeyframeTrack.Keyframe(0, new Vec3(0, 0, 0), "linear"),
                new KeyframeTrack.Keyframe(2, new Vec3(10, 0, 0), "linear"));

        assertEquals(5.0, t.sample(1, true).x(), 1e-9);
        assertEquals(10.0, t.sample(0, true).x(), 1e-9, "t=0 反向即末帧");
        assertEquals(0.0, t.sample(2, true).x(), 1e-9, "t=末帧 反向即首帧");
    }

    @Test
    @DisplayName("时间跨度为 0 的重复帧不产生 NaN")
    void zeroSpanFramesStayFinite() {
        var t = track("linear",
                new KeyframeTrack.Keyframe(1, new Vec3(3, 0, 0), "linear"),
                new KeyframeTrack.Keyframe(1, new Vec3(7, 0, 0), "linear"));

        var v = t.sample(1, false);
        assertEquals(Double.isNaN(v.x()), false, "不应产生 NaN");
    }

    @Test
    @DisplayName("interp 缺省按 linear 处理")
    void nullInterpTreatedAsLinear() {
        var t = track(null,
                new KeyframeTrack.Keyframe(0, new Vec3(0, 0, 0), null),
                new KeyframeTrack.Keyframe(2, new Vec3(10, 0, 0), null));
        assertEquals(5.0, t.sample(1, false).x(), 1e-9);
    }

    @Test
    @DisplayName("normalize：循环动画对 length 取模，非循环钳制到 length")
    void normalizeTime() {
        var loop = new AnimationClip("walk", true, 2.0, java.util.Map.of(), List.of());
        assertEquals(0.0, loop.normalize(0), 1e-9);
        assertEquals(1.0, loop.normalize(1), 1e-9);
        assertEquals(0.5, loop.normalize(2.5), 1e-9);
        assertEquals(1.5, loop.normalize(3.5), 1e-9);
        assertEquals(1.5, loop.normalize(-0.5), 1e-9, "负时间应回绕到 [0,length)");

        var once = new AnimationClip("attack", false, 2.0, java.util.Map.of(), List.of());
        assertEquals(2.0, once.normalize(5), 1e-9);
        assertEquals(1.0, once.normalize(1), 1e-9);
    }

    @Test
    @DisplayName("length 为 0 的退化动画不会除零")
    void degenerateLengthIsSafe() {
        var clip = new AnimationClip("broken", true, 0, java.util.Map.of(), List.of());
        assertEquals(0.0, clip.normalize(3.3), 1e-9);
    }
}