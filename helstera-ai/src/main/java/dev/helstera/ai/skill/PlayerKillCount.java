package dev.helstera.ai.skill;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Counts player kills for a managed mob instance independently of kill-streak windows. */
public final class PlayerKillCount {

    private final Map<Integer, Integer> counts = new ConcurrentHashMap<>();

    /** Records one player victim and returns the new lifetime count. */
    public int record(int instanceId) {
        return counts.merge(instanceId, 1, Integer::sum);
    }

    public int count(int instanceId) {
        return counts.getOrDefault(instanceId, 0);
    }

    public void forget(int instanceId) {
        counts.remove(instanceId);
    }

    public void clear() {
        counts.clear();
    }
}
