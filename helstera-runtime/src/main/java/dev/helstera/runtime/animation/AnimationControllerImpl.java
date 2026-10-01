package dev.helstera.runtime.animation;

import dev.helstera.api.Vec3;
import dev.helstera.api.animation.AnimationController;
import dev.helstera.api.animation.AnimationOptions;
import dev.helstera.api.event.AnimationEndEvent;
import dev.helstera.api.event.AnimationMarkerEvent;
import dev.helstera.api.event.AnimationStartEvent;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.core.animation.AnimationClip;
import dev.helstera.core.animation.KeyframeTrack;
import dev.helstera.core.model.ModelDefinitionImpl;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 动画控制器实现：播放/停止/暂停/恢复/重置、淡入淡出、优先级与打断、
 * 循环/变速/反向/指定时间开始、事件标记触发。
 */
public final class AnimationControllerImpl implements AnimationController {

    private static final class ActiveClip {
        AnimationClip clip;
        AnimationOptions opts;
        double time;
        boolean finished;

        ActiveClip(AnimationClip clip, AnimationOptions opts, double startAt) {
            this.clip = clip;
            this.opts = opts;
            this.time = opts.reverse() ? clip.length() - startAt : startAt;
        }
    }

    private final ModelInstance owner;
    private final ModelDefinitionImpl model;
    private final HelsteraEventBus bus;

    private ActiveClip current;
    private ActiveClip previous;
    /** 过渡进度（秒），transition 期间向 current 混合。 */
    private double blendT;
    private boolean paused;
    private String state = "IDLE";
    private int playGeneration;

    public AnimationControllerImpl(ModelInstance owner, ModelDefinitionImpl model, HelsteraEventBus bus) {
        this.owner = owner;
        this.model = model;
        this.bus = bus;
    }

    @Override
    public boolean play(String animation, AnimationOptions options) {
        AnimationClip clip = model.animation(animation);
        if (clip == null) return false;
        AnimationOptions opts = options == null ? AnimationOptions.defaults() : options;
        if (current != null) {
            boolean currentActive = !current.finished;
            if (currentActive && current.opts.priority() > opts.priority()) return false;
            if (currentActive && current.opts.priority() == opts.priority() && !current.opts.interruptible()) {
                return false;
            }
            previous = current;
        }
        current = new ActiveClip(clip, opts, opts.startAt());
        blendT = 0;
        paused = false;
        state = "PLAYING";
        playGeneration++;
        bus.post(new AnimationStartEvent(owner, animation, opts.loop()));
        return true;
    }

    @Override
    public void stop(String animation) {
        if (current != null && current.clip.name().equals(animation)) {
            current = null;
            state = "IDLE";
        }
        if (previous != null && previous.clip.name().equals(animation)) previous = null;
    }

    @Override
    public void stopAll() {
        current = null;
        previous = null;
        state = "IDLE";
    }

    @Override
    public void pause() {
        paused = true;
        state = current == null ? "IDLE" : "PAUSED";
    }

    @Override
    public void resume() {
        paused = false;
        state = current == null ? "IDLE" : "PLAYING";
    }

    @Override
    public void reset() {
        stopAll();
        blendT = 0;
        paused = false;
    }

    @Override
    public Optional<String> currentAnimation() {
        return current == null ? Optional.empty() : Optional.of(current.clip.name());
    }

    @Override
    public String state() {
        return state;
    }

    @Override
    public boolean hasAnimation(String name) {
        return model.animation(name) != null;
    }

    /** 由调度器每 Tick 推进（dt 秒）。 */
    public void tick(float dt) {
        if (current == null || paused) return;
        float fade = Math.max(current.opts.transition(), 0.0001f);
        blendT = Math.min(blendT + dt, fade);
        if (blendT >= fade) previous = null;

        double delta = dt * current.opts.speed() * (current.opts.reverse() ? -1 : 1);
        double oldTime = current.time;
        current.time += delta;
        fireMarkers(current.clip, oldTime, current.time, current.opts.reverse());

        if (!current.opts.loop()) {
            double len = current.clip.length();
            boolean done = current.opts.reverse() ? current.time <= 0 : current.time >= len;
            if (done) {
                current.finished = true;
                String name = current.clip.name();
                current = null;
                previous = null;
                state = "IDLE";
                bus.post(new AnimationEndEvent(owner, name));
            }
        } else if (current.time >= current.clip.length() || current.time < 0) {
            // 循环回绕：重算标记区间，避免重复或漏发
            current.time = current.clip.normalize(current.time);
        }
    }

    private void fireMarkers(AnimationClip clip, double from, double to, boolean reverse) {
        if (clip.events().isEmpty()) return;
        for (AnimationClip.EventMarker m : clip.events()) {
            boolean hit;
            if (!reverse) {
                hit = (from <= to) ? (m.time() > from && m.time() <= to)
                        : (m.time() > from || m.time() <= to);
            } else {
                hit = (from >= to) ? (m.time() < from && m.time() >= to)
                        : (m.time() < from || m.time() >= to);
            }
            if (hit) {
                bus.post(new AnimationMarkerEvent(owner, clip.name(), m.marker(), m.data()));
            }
        }
    }

    /**
     * 采样当前姿态：静止姿势 + （过渡期旧动画混合）+ 当前动画。
     * 返回每骨骼姿态；无动画时返回空 Map。
     */
    public Map<String, BonePose> samplePose() {
        Map<String, BonePose> out = new HashMap<>();
        if (previous != null && current != null) {
            double fade = Math.max(current.opts.transition(), 0.0001f);
            double alpha = blendT / fade;
            Map<String, BonePose> a = sampleClip(previous);
            Map<String, BonePose> b = sampleClip(current);
            var keys = new java.util.HashSet<String>();
            keys.addAll(a.keySet());
            keys.addAll(b.keySet());
            for (String k : keys) {
                BonePose pa = a.getOrDefault(k, BonePose.ZERO);
                BonePose pb = b.getOrDefault(k, BonePose.ZERO);
                out.put(k, BonePose.lerp(pa, pb, alpha));
            }
        } else if (current != null) {
            out.putAll(sampleClip(current));
        }
        return out;
    }

    private Map<String, BonePose> sampleClip(ActiveClip active) {
        Map<String, BonePose> out = new LinkedHashMap<>();
        double t = active.clip.normalize(active.time);
        for (var e : active.clip.bones().entrySet()) {
            String bone = e.getKey();
            KeyframeTrack.BoneTracks tracks = e.getValue();
            Vec3 rot = sampleChannel(tracks, "rotation", t);
            Vec3 pos = sampleChannel(tracks, "position", t);
            Vec3 scl = sampleChannel(tracks, "scale", t);
            out.put(bone, new BonePose(rot, pos, scl));
        }
        return out;
    }

    private Vec3 sampleChannel(KeyframeTrack.BoneTracks tracks, String channel, double t) {
        KeyframeTrack track = tracks.channels().get(channel);
        if (track == null) return Vec3.ZERO;
        return track.sample(t, current != null && current.opts.reverse() && track == tracks.channels().get(channel));
    }

    public int generation() {
        return playGeneration;
    }
}
