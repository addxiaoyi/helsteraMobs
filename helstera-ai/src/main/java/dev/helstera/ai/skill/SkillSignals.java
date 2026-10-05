package dev.helstera.ai.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill signal bus: lets one skill's signal trigger another skill's {@code on-signal}.
 *
 * <p>Mirrors MythicMobs' {@code ~onSignal} / {@code signal} mechanics. Typical use:
 * "boss enters phase 2, broadcast signal phase2, summon adds / open arena mechanism",
 * instead of writing that whole side-effect chain inline in the phase actions.
 *
 * <p>Two scopes. Instance-scoped (default) is received only by that same mob;
 * global is broadcast to every mob. Instance scope is the default on purpose:
 * with global-first semantics a pack of same-named mobs would cross-trigger each
 * other, showing up as "a few of them randomly enrage" - nearly impossible to diagnose.</p>
 *
 * <p>No buffering: signals are dispatched at emit time only. Late subscribers do not
 * get history, and nothing queues up after an entity dies (no ghost triggers).</p>
 *
 * <p>Thread safe via a concurrent map plus a pre-dispatch snapshot. The skill chain
 * normally runs on the main thread.</p>
 *
 * <p>NOTE: comments are ASCII-only in this file on purpose. An earlier revision of this
 * file landed on disk as GBK and broke the build with "illegal UTF-8 byte" errors, which
 * then got mangled further by a re-encode attempt. ASCII comments cannot drift.</p>
 */
public final class SkillSignals {

    /**
     * Global bus instance.
     *
     * <p>{@code SkillExtras}' action table is static and cannot receive an injected
     * instance, yet the {@code signal} action needs the bus. A singleton bridges the
     * two; making the action table stateful instead would ripple through every
     * registered action.</p>
     */
    private static volatile SkillSignals global = new SkillSignals();

    public static SkillSignals global() {
        return global;
    }

    /** Test hook: replace the global instance to avoid cross-test leakage. */
    public static void resetGlobal(SkillSignals bus) {
        global = bus == null ? new SkillSignals() : bus;
    }

    /** Callback invoked when a signal arrives. */
    public interface Listener {
        /**
         * @param signal signal name
         * @param payload payload, may be null
         * @param sourceId emitting instance id; null for a global signal
         */
        void onSignal(String signal, String payload, Integer sourceId);
    }

    /** Listener key; carries scope so same-named signals in different scopes never collide. */
    private static String key(Integer scopeId, String signal) {
        return (scopeId == null ? "*" : scopeId) + " " + signal;
    }

    private final Map<String, Listener> listeners = new ConcurrentHashMap<>();

    public int listenerCount() {
        return listeners.size();
    }

    public int listenerCount(String signal) {
        int n = 0;
        String suffix = " " + signal;
        for (String k : listeners.keySet()) {
            if (k.endsWith(suffix)) n++;
        }
        return n;
    }

    /**
     * Subscribe.
     *
     * @param scopeId instance id, null for global
     * @param signal signal name. Case is preserved deliberately: an author may intend
     *               {@code phase2} and {@code Phase2} to be different signals.
     * @param listener callback; null is ignored
     */
    public void subscribe(Integer scopeId, String signal, Listener listener) {
        if (signal == null || signal.isBlank() || listener == null) return;
        listeners.put(key(scopeId, signal), listener);
    }

    /**
     * Unsubscribe.
     *
     * @return true if a listener was actually removed
     */
    public boolean unsubscribe(Integer scopeId, String signal) {
        if (signal == null) return false;
        return listeners.remove(key(scopeId, signal)) != null;
    }

    /**
     * Emit a signal.
     *
     * <p>Takes a snapshot before dispatching: a callback may itself emit or unsubscribe,
     * and iterating the live map would throw {@code ConcurrentModificationException}.</p>
     *
     * @return number of listeners that actually received the signal
     */
    public int emit(Integer scopeId, String signal, String payload) {
        if (signal == null || signal.isBlank()) return 0;
        List<Listener> targets = new ArrayList<>(2);
        // A mob should hear its own signal.
        Listener own = listeners.get(key(scopeId, signal));
        if (own != null) targets.add(own);
        if (scopeId != null) {
            Listener globalListener = listeners.get(key(null, signal));
            if (globalListener != null) targets.add(globalListener);
        }

        int n = 0;
        for (Listener l : targets) {
            try {
                l.onSignal(signal, payload, scopeId);
                n++;
            } catch (Throwable t) {
                // One bad listener must not stop the others: otherwise a single broken
                // config silently starves every other skill subscribed to this signal.
                SkillFaults.swallow("signal:" + signal, t);
            }
        }
        return n;
    }

    /** Drop all subscriptions for one instance. Called on removal so stale listeners
     *  do not keep referencing dead instances. */
    public void purgeInstance(int instanceId) {
        String prefix = instanceId + " ";
        listeners.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** Clear every subscription. */
    public void clear() {
        listeners.clear();
    }

    /** Deduplicated set of subscribed signal names, for debug display. */
    public java.util.Set<String> signals() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String k : listeners.keySet()) {
            int sp = k.indexOf(' ');
            if (sp >= 0 && sp + 1 < k.length()) out.add(k.substring(sp + 1));
        }
        return out;
    }

    /** Emit a payload-less global signal. */
    public int emitGlobal(String signal) {
        return emit(null, signal, null);
    }

    /** Emit an instance-scoped signal. */
    public int emit(int instanceId, String signal, String payload) {
        return emit(Integer.valueOf(instanceId), signal, payload);
    }
}