package dev.helstera.api.animation;

import java.util.Optional;

/**
 * 动画控制器。方法均要求主线程调用。
 */
public interface AnimationController {

    /**
     * 播放动画。
     *
     * @return false 表示动画不存在或被更高优先级动画拒绝打断
     */
    boolean play(String animation, AnimationOptions options);

    void stop(String animation);

    void stopAll();

    void pause();

    void resume();

    void reset();

    /** 当前正在播放的动画名（无则 empty）。 */
    Optional<String> currentAnimation();

    /** 当前动画状态：PLAYING / PAUSED / IDLE。 */
    String state();

    /** 是否存在指定动画。 */
    boolean hasAnimation(String name);
}
