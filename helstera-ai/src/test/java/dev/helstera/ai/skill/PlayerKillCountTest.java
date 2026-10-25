package dev.helstera.ai.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerKillCountTest {

    @Test
    void recordsCountsPerInstanceAndDoesNotShareState() {
        var counts = new PlayerKillCount();

        assertEquals(0, counts.count(1));
        assertEquals(1, counts.record(1));
        assertEquals(2, counts.record(1));
        assertEquals(1, counts.record(2));
        assertEquals(2, counts.count(1));
        assertEquals(1, counts.count(2));
    }

    @Test
    void forgetRemovesOnlyTheSelectedInstance() {
        var counts = new PlayerKillCount();
        counts.record(1);
        counts.record(2);

        counts.forget(1);

        assertEquals(0, counts.count(1));
        assertEquals(1, counts.count(2));
    }

    @Test
    void clearRemovesAllInstanceCounts() {
        var counts = new PlayerKillCount();
        counts.record(1);
        counts.record(2);

        counts.clear();

        assertEquals(0, counts.count(1));
        assertEquals(0, counts.count(2));
    }
}
