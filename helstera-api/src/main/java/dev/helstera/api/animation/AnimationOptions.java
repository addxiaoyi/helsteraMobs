package dev.helstera.api.animation;

/**
 * 播放动画的参数（建造器）。
 */
public final class AnimationOptions {

    private boolean loop = false;
    private float speed = 1.0f;
    private boolean reverse = false;
    private double startAt = 0.0;
    /** 过渡时间（秒）：从当前姿势混合到目标动画。 */
    private float transition = 0.2f;
    /** 优先级：数值越大越不容易被打断。 */
    private int priority = 0;
    /** 是否可被其它动画打断。 */
    private boolean interruptible = true;
    /** 淡出时间（秒），停止时使用。 */
    private float fadeOut = 0.1f;

    public static AnimationOptions of(String none) {
        return new AnimationOptions();
    }

    public static AnimationOptions defaults() {
        return new AnimationOptions();
    }

    public boolean loop() { return loop; }

    public AnimationOptions loop(boolean v) { this.loop = v; return this; }

    public float speed() { return speed; }

    public AnimationOptions speed(float v) { this.speed = v; return this; }

    public boolean reverse() { return reverse; }

    public AnimationOptions reverse(boolean v) { this.reverse = v; return this; }

    public double startAt() { return startAt; }

    public AnimationOptions startAt(double v) { this.startAt = v; return this; }

    public float transition() { return transition; }

    public AnimationOptions transition(float v) { this.transition = v; return this; }

    public int priority() { return priority; }

    public AnimationOptions priority(int v) { this.priority = v; return this; }

    public boolean interruptible() { return interruptible; }

    public AnimationOptions interruptible(boolean v) { this.interruptible = v; return this; }

    public float fadeOut() { return fadeOut; }

    public AnimationOptions fadeOut(float v) { this.fadeOut = v; return this; }
}
